package com.kista.api.finance.domain.model;

import java.util.List;
import java.util.UUID;

// 일괄 등록 미리보기 — 소스월의 자산·수입·소비·저축을 카테고리 계층 그룹 트리로 묶은 결과
public record BulkRegisterPreview(
        List<GroupNode> asset,
        List<GroupNode> income,
        List<GroupNode> expense,
        List<GroupNode> saving
) {
    // 카테고리 경로 한 단계 — sortOrder는 각 카테고리 자신의 관리자 정렬순서
    public record PathSegment(UUID id, String name, int sortOrder) {}

    // 미리보기 한 행 — id는 소스월 원본(스냅샷/거래) id. 자산 전용 필드는 거래 행에서 null
    public record Item(
            UUID id,
            UUID categoryId,
            String categoryName,            // 리프(자신) 카테고리명
            List<PathSegment> categoryPath, // 루트→자신 전체 경로
            String memo,                    // null 허용
            long amount,
            AssetClass assetClass,
            Market market,
            String strategy,
            UUID accountId,
            String accountName,
            String accountInstitution,
            String accountOwner
    ) {}

    // 카테고리 경로 기준 그룹 노드 — 항목은 마지막 세그먼트 노드의 items에 담긴다
    public record GroupNode(UUID id, String name, int sortOrder, List<Item> items, List<GroupNode> children) {}
}
