package com.kista.application.service.finance;

import com.kista.domain.model.finance.AssetClass;
import com.kista.domain.model.finance.AssetSnapshot;
import com.kista.domain.model.finance.AssetSnapshotCommand;
import com.kista.domain.model.finance.FinanceCategory;
import com.kista.domain.model.finance.Market;
import com.kista.domain.port.out.AssetSnapshotPort;
import com.kista.domain.port.out.FinanceCategoryPort;
import com.kista.domain.port.out.FinanceGroupPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssetSnapshotService 단위 테스트")
class AssetSnapshotServiceTest {

    @Mock AssetSnapshotPort assetSnapshotPort;
    @Mock FinanceGroupPort financeGroupPort;
    @Mock FinanceCategoryPort financeCategoryPort;
    @InjectMocks AssetSnapshotService assetSnapshotService;

    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final UUID snapshotId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    private AssetSnapshot existingSnapshot() {
        return new AssetSnapshot(snapshotId, groupId, categoryId, accountId, userId,
                LocalDate.of(2026, 1, 1), AssetClass.CASH, Market.DOMESTIC, null, 1_000_000L, null);
    }

    private AssetSnapshotCommand command() {
        return new AssetSnapshotCommand(categoryId, accountId, LocalDate.of(2026, 2, 1),
                AssetClass.EQUITY, Market.GLOBAL, "장기투자", 2_000_000L);
    }

    // create/update는 저장 전에 categoryId 소유권·타입(ASSET)을 검증하므로 그 경로를 타는 테스트는
    // 이 그룹 소유의 ASSET 카테고리를 stub해야 한다.
    private FinanceCategory usableCategory() {
        return new FinanceCategory(categoryId, groupId, null, userId, FinanceCategory.Type.ASSET, "투자", 0, null);
    }

    @Test
    @DisplayName("list는 resolveGroupId로 얻은 groupId로 조회")
    void list_resolvesGroupId() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        when(assetSnapshotPort.findByGroupId(groupId, null, null, null))
                .thenReturn(List.of(existingSnapshot()));

        List<AssetSnapshot> result = assetSnapshotService.list(userId, null, null, null, null);

        assertThat(result).hasSize(1);
        verify(financeGroupPort).resolveGroupId(userId, null);
    }

    @Test
    @DisplayName("create는 resolveGroupId 호출 후 스냅샷 저장")
    void create_resolvesGroupIdThenSaves() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(assetSnapshotPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AssetSnapshot result = assetSnapshotService.create(userId, null, command());

        assertThat(result.groupId()).isEqualTo(groupId);
        assertThat(result.createdBy()).isEqualTo(userId);
        assertThat(result.amount()).isEqualTo(2_000_000L);
        verify(financeGroupPort).resolveGroupId(userId, null);
    }

    @Test
    @DisplayName("update는 load-then-verify-membership 패턴")
    void update_loadsThenVerifiesMembership() {
        when(assetSnapshotPort.findByIdOrThrow(snapshotId)).thenReturn(existingSnapshot());
        when(financeGroupPort.resolveGroupId(userId, groupId)).thenReturn(groupId);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(assetSnapshotPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AssetSnapshot result = assetSnapshotService.update(snapshotId, userId, command());

        assertThat(result.amount()).isEqualTo(2_000_000L);
        assertThat(result.createdBy()).isEqualTo(userId); // 기존 createdBy 유지
        verify(financeGroupPort).resolveGroupId(userId, groupId);
    }

    @Test
    @DisplayName("ASSET이 아닌 카테고리는 자산 스냅샷에 사용할 수 없음")
    void create_nonAssetTypeCategory_rejected() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        FinanceCategory expenseCategory = new FinanceCategory(categoryId, groupId, null, userId,
                FinanceCategory.Type.EXPENSE, "식비", 0, null);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(expenseCategory);

        assertThatThrownBy(() -> assetSnapshotService.create(userId, null, command()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(assetSnapshotPort, never()).save(any());
    }

    @Test
    @DisplayName("다른 그룹 소유 카테고리를 지정하면 SecurityException")
    void create_categoryFromOtherGroup_rejected() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        FinanceCategory otherGroupCategory = new FinanceCategory(categoryId, UUID.randomUUID(), null, userId,
                FinanceCategory.Type.ASSET, "투자", 0, null);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(otherGroupCategory);

        assertThatThrownBy(() -> assetSnapshotService.create(userId, null, command()))
                .isInstanceOf(SecurityException.class);
        verify(assetSnapshotPort, never()).save(any());
    }

    @Test
    @DisplayName("delete는 load-then-verify-membership 후 softDelete 호출")
    void delete_callsSoftDelete() {
        when(assetSnapshotPort.findByIdOrThrow(snapshotId)).thenReturn(existingSnapshot());
        when(financeGroupPort.resolveGroupId(userId, groupId)).thenReturn(groupId);

        assetSnapshotService.delete(snapshotId, userId);

        verify(financeGroupPort).resolveGroupId(userId, groupId);
        verify(assetSnapshotPort).softDelete(snapshotId);
    }
}
