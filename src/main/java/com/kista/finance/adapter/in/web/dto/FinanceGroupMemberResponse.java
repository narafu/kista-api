package com.kista.finance.adapter.in.web.dto;

import com.kista.finance.domain.model.FinanceGroupMember;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record FinanceGroupMemberResponse(
        @Schema(description = "사용자 ID")
        UUID userId,
        @Schema(description = "닉네임")
        String nickname,
        @Schema(description = "그룹 내 역할", example = "OWNER")
        String role
) {
    public static FinanceGroupMemberResponse from(FinanceGroupMember m, String nickname) {
        return new FinanceGroupMemberResponse(m.userId(), nickname, m.role().name());
    }
}
