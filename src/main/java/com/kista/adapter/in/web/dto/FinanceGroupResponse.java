package com.kista.adapter.in.web.dto;

import com.kista.domain.model.finance.FinanceGroup;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record FinanceGroupResponse(
        @Schema(description = "그룹 고유 ID")
        UUID id,
        @Schema(description = "그룹명", example = "개인")
        String name,
        @Schema(description = "가입 시 자동 생성된 1인 개인 그룹 여부")
        boolean personal
) {
    public static FinanceGroupResponse from(FinanceGroup g) {
        return new FinanceGroupResponse(g.id(), g.name(), g.personal());
    }
}
