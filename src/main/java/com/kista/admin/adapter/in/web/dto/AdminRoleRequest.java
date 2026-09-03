package com.kista.admin.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.kista.sharedkernel.UserRole;

// 관리자 역할 변경 요청 body
public record AdminRoleRequest(
        @Schema(description = "부여할 역할")
        UserRole role) {}
