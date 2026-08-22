package com.kista.adapter.in.web.dto;

import com.kista.domain.model.finance.FinanceGroup;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// name은 rename API가 없어 항상 고정값이라 DB 컬럼으로 두지 않고 응답 시점에 고정 문자열로 채운다.
public record FinanceGroupResponse(
        @Schema(description = "그룹 고유 ID")
        UUID id,
        @Schema(description = "그룹명", example = "가계부")
        String name
) {
    public static FinanceGroupResponse from(FinanceGroup g) {
        return new FinanceGroupResponse(g.id(), "가계부");
    }
}
