package com.kista.api.admin.adapter.in.web.dto;

import com.kista.api.user.domain.model.UserSummary;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.kista.shared.kernel.UserRole;
import com.kista.shared.kernel.UserStatus;

public record AdminUserResponse(
        @Schema(description = "사용자 고유 ID") UUID id,
        @Schema(description = "닉네임") String nickname,
        @Schema(description = "계정 상태") UserStatus status,
        @Schema(description = "역할") UserRole role,
        @Schema(description = "가입 일시") Instant createdAt
) {
    public static AdminUserResponse from(UserSummary view) {
        return new AdminUserResponse(view.id(), view.nickname(), view.status(),
                view.role(), view.createdAt());
    }

    public static List<AdminUserResponse> fromList(List<UserSummary> views) {
        return views.stream().map(AdminUserResponse::from).toList();
    }
}
