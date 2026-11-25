package com.kista.api.finance.domain.model;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// 예산 대비 실적 — 예산 카테고리 트리와 예산 없는 실적 카테고리
public record BudgetProgress(List<Node> nodes, List<Unbudgeted> unbudgeted) {

    public record Node(
            UUID categoryId,
            String categoryName,
            int depth,                  // 루트 0
            boolean isGroup,            // true면 자체 예산 없이 하위 예산 합만 보여주는 소계 노드
            @Nullable UUID budgetId,    // isGroup=false일 때만
            long allocated,             // 월 할당액 x 유효 개월 수
            long actual,
            long remaining,             // 음수면 초과
            BigDecimal usageRatio,      // actual/allocated(scale 4), allocated 0이면 0
            List<Node> children
    ) {}

    public record Unbudgeted(UUID categoryId, String categoryName, long amount) {}
}
