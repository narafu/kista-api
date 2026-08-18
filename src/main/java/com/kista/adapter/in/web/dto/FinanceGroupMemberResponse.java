package com.kista.adapter.in.web.dto;

import com.kista.domain.model.finance.FinanceGroupMember;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record FinanceGroupMemberResponse(
        @Schema(description = "사용자 ID")
        UUID userId,
        @Schema(description = "그룹 내 역할", example = "OWNER")
        String role
) {
    public static FinanceGroupMemberResponse from(FinanceGroupMember m) {
        return new FinanceGroupMemberResponse(m.userId(), m.role().name());
    }
}
