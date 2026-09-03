package com.kista.finance.adapter.in.web.dto;

import com.kista.finance.domain.model.FinanceCategory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

public record FinanceCategoryResponse(
        @Schema(description = "카테고리 고유 ID")
        UUID id,
        @Schema(description = "그룹 ID (시스템 전역 카테고리면 null)")
        UUID groupId,
        @Schema(description = "상위 카테고리 ID (L1이면 null)")
        UUID parentId,
        @Schema(description = "카테고리 타입", example = "EXPENSE")
        FinanceCategory.Type type,
        @Schema(description = "카테고리명", example = "식비")
        String name,
        @Schema(description = "정렬 순서", example = "10")
        int sortOrder,
        @Schema(description = "시스템 전역 카테고리 여부 (true면 수정·삭제 불가)")
        boolean system,
        @Schema(description = "하위 카테고리 목록 (임의 depth로 재귀 중첩)")
        List<FinanceCategoryResponse> children
) {
    public static FinanceCategoryResponse from(FinanceCategory c, List<FinanceCategoryResponse> children) {
        return new FinanceCategoryResponse(c.id(), c.groupId(), c.parentId(), c.type(), c.name(), c.sortOrder(),
                c.isSystem(), children);
    }
}
