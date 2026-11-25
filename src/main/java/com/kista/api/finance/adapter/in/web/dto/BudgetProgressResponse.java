package com.kista.api.finance.adapter.in.web.dto;

import com.kista.api.finance.domain.model.BudgetProgress;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record BudgetProgressResponse(
        @Schema(description = "예산 카테고리 트리")
        List<Node> nodes,
        @Schema(description = "예산에 덮이지 않은 실적 카테고리 (카테고리 표시 순서)")
        List<Unbudgeted> unbudgeted
) {
    @Schema(name = "BudgetProgressNode")
    public record Node(
            UUID categoryId,
            String categoryName,
            @Schema(description = "루트 0") int depth,
            @Schema(description = "true면 자체 예산 없는 하위 합계 노드") boolean isGroup,
            @Schema(description = "자체 예산 ID (isGroup=false일 때만)") @Nullable UUID budgetId,
            @Schema(description = "월 할당액 x 유효 개월 수") long allocated,
            long actual,
            @Schema(description = "allocated - actual, 음수면 초과") long remaining,
            @Schema(description = "actual/allocated 0~, scale 4 (allocated 0이면 0)", example = "0.8333") BigDecimal usageRatio,
            List<Node> children
    ) {
        static Node from(BudgetProgress.Node n) {
            return new Node(n.categoryId(), n.categoryName(), n.depth(), n.isGroup(), n.budgetId(), n.allocated(),
                    n.actual(), n.remaining(), n.usageRatio(), n.children().stream().map(Node::from).toList());
        }
    }

    @Schema(name = "BudgetProgressUnbudgeted")
    public record Unbudgeted(UUID categoryId, String categoryName, long amount) {}

    public static BudgetProgressResponse from(BudgetProgress p) {
        return new BudgetProgressResponse(p.nodes().stream().map(Node::from).toList(),
                p.unbudgeted().stream().map(u -> new Unbudgeted(u.categoryId(), u.categoryName(), u.amount())).toList());
    }
}
