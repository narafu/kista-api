package com.kista.api.finance.adapter.in.web.dto;

import com.kista.api.finance.domain.model.BulkRegisterPreview;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

// 일괄 등록 미리보기 — UI 상태값(included)은 내려주지 않는다
public record BulkRegisterPreviewResponse(
        List<Node> asset,
        List<Node> income,
        List<Node> expense,
        List<Node> saving
) {
    public record PathSegment(UUID id, String name, int sortOrder) {}

    public record Item(
            UUID id,
            UUID categoryId,
            String categoryName,
            List<PathSegment> categoryPath,
            @Nullable String memo,
            long amount,
            @Nullable String assetClass,
            @Nullable String market,
            @Nullable String strategy,
            @Nullable UUID accountId,
            @Nullable String accountName,
            @Nullable String accountInstitution,
            @Nullable String accountOwner
    ) {
        static Item from(BulkRegisterPreview.Item i) {
            return new Item(i.id(), i.categoryId(), i.categoryName(),
                    i.categoryPath().stream().map(s -> new PathSegment(s.id(), s.name(), s.sortOrder())).toList(),
                    i.memo(), i.amount(),
                    i.assetClass() == null ? null : i.assetClass().name(),
                    i.market() == null ? null : i.market().name(),
                    i.strategy(), i.accountId(), i.accountName(), i.accountInstitution(), i.accountOwner());
        }
    }

    public record Node(UUID id, String name, int sortOrder, List<Item> items, List<Node> children) {
        static Node from(BulkRegisterPreview.GroupNode n) {
            return new Node(n.id(), n.name(), n.sortOrder(),
                    n.items().stream().map(Item::from).toList(),
                    n.children().stream().map(Node::from).toList());
        }
    }

    public static BulkRegisterPreviewResponse from(BulkRegisterPreview p) {
        return new BulkRegisterPreviewResponse(nodes(p.asset()), nodes(p.income()), nodes(p.expense()), nodes(p.saving()));
    }

    private static List<Node> nodes(List<BulkRegisterPreview.GroupNode> list) {
        return list.stream().map(Node::from).toList();
    }
}
