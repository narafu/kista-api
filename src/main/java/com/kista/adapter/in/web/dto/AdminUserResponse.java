package com.kista.adapter.in.web.dto;

import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.user.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminUserResponse(
        @Schema(description = "사용자 고유 ID") UUID id,
        @Schema(description = "닉네임") String nickname,
        @Schema(description = "계정 상태") User.UserStatus status,
        @Schema(description = "역할") User.UserRole role,
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
