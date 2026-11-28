package com.kista.api.finance.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceCategorySubtreeTest {

    private static FinanceCategory cat(UUID id, UUID parentId) {
        return new FinanceCategory(id, null, parentId, null, FinanceCategory.Type.ASSET, "c", 0, null);
    }

    @Test
    void subtreeIds는_자신과_모든_후손을_포함하고_형제는_제외한다() {
        UUID l1 = UUID.randomUUID();
        UUID l2 = UUID.randomUUID();
        UUID l3 = UUID.randomUUID();
        UUID sibling = UUID.randomUUID();
        // 후손이 목록 앞에 와도 잡히도록 역순으로 둔다
        List<FinanceCategory> all = List.of(cat(l3, l2), cat(sibling, null), cat(l2, l1), cat(l1, null));

        assertThat(FinanceCategory.subtreeIds(all, l1)).containsExactlyInAnyOrder(l1, l2, l3);
        assertThat(FinanceCategory.subtreeIds(all, l2)).containsExactlyInAnyOrder(l2, l3);
    }

    @Test
    void rootId가_목록에_없어도_자신은_포함한다() {
        UUID root = UUID.randomUUID();

        assertThat(FinanceCategory.subtreeIds(List.of(), root)).containsExactly(root);
    }
}
