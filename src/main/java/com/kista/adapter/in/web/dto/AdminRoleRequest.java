package com.kista.adapter.in.web.dto;

import com.kista.domain.model.user.User;
import io.swagger.v3.oas.annotations.media.Schema;

// 관리자 역할 변경 요청 body
public record AdminRoleRequest(
        @Schema(description = "부여할 역할")
        User.UserRole role) {}
