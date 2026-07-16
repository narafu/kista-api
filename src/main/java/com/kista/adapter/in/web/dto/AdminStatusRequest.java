package com.kista.adapter.in.web.dto;

import com.kista.domain.model.user.User;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

// 관리자 사용자 상태 변경 요청 body — ACTIVE(승인) / REJECTED(거절)
// reason은 REJECTED일 때만 사용, ACTIVE면 무시
public record AdminStatusRequest(
        @Schema(description = "변경할 계정 상태", example = "ACTIVE")
        User.UserStatus status,
        @Schema(description = "반려 사유 (status=REJECTED일 때만 사용, ACTIVE면 무시)")
        @Size(max = 500) String reason) {}
