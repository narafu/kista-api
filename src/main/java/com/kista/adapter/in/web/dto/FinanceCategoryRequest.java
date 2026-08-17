package com.kista.adapter.in.web.dto;

import com.kista.domain.model.finance.FinanceCategory;
import com.kista.domain.model.finance.FinanceCategoryCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

// 등록·수정 공용 — parentId/type은 수정 시 무시되지만(카테고리 계층·타입 불변) 등록 시 필수라 @NotNull을 걸지 않는다.
public record FinanceCategoryRequest(
        @Schema(description = "상위 카테고리 ID (L1이면 null, 등록 시에만 사용 — 수정 시 무시)")
        UUID parentId,
        @Schema(description = "카테고리 타입 (등록 시 필수, 수정 시 무시)", example = "EXPENSE")
        FinanceCategory.Type type,
        @Schema(description = "카테고리명", example = "식비")
        @NotBlank String name,
        @Schema(description = "정렬 순서", example = "10")
        int sortOrder
) {
    public FinanceCategoryCommand toCommand() {
        return new FinanceCategoryCommand(parentId, type, name, sortOrder);
    }
}
