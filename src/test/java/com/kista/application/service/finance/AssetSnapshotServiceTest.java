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
import java.util.Optional;
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

    private AssetSnapshot personalSnapshot() {
        return new AssetSnapshot(snapshotId, null, categoryId, accountId, userId,
                LocalDate.of(2026, 1, 1), AssetClass.CASH, Market.DOMESTIC, null, 1_000_000L, null);
    }

    private AssetSnapshotCommand command() {
        return new AssetSnapshotCommand(categoryId, accountId, LocalDate.of(2026, 2, 1),
                AssetClass.EQUITY, Market.GLOBAL, "장기투자", 2_000_000L);
    }

    private FinanceCategory usableCategory() {
        return new FinanceCategory(categoryId, null, null, userId, FinanceCategory.Type.ASSET, "투자", 0, null);
    }

    @Test
    @DisplayName("list는 findCurrentGroupId로 얻은 currentGroupId로 조회")
    void list_queriesWithCurrentGroupId() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        when(assetSnapshotPort.findMyScope(userId, groupId, null, null, null))
                .thenReturn(List.of(personalSnapshot()));

        List<AssetSnapshot> result = assetSnapshotService.list(userId, null, null, null, null);

        assertThat(result).hasSize(1);
        verify(assetSnapshotPort).findMyScope(userId, groupId, null, null, null);
    }

    @Test
    @DisplayName("create는 requestedGroupId를 무시하고 개인 소유(groupId=null)로 저장")
    void create_alwaysSavesAsPersonalOwnership() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(assetSnapshotPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AssetSnapshot result = assetSnapshotService.create(userId, groupId, command());

        assertThat(result.groupId()).isNull();
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.amount()).isEqualTo(2_000_000L);
    }

    @Test
    @DisplayName("update는 load-then-verify 패턴")
    void update_loadsThenVerifiesAccess() {
        when(assetSnapshotPort.findByIdOrThrow(snapshotId)).thenReturn(personalSnapshot());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(assetSnapshotPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AssetSnapshot result = assetSnapshotService.update(snapshotId, userId, command());

        assertThat(result.amount()).isEqualTo(2_000_000L);
        assertThat(result.userId()).isEqualTo(userId); // 기존 소유자 유지
    }

    @Test
    @DisplayName("ASSET이 아닌 카테고리는 자산 스냅샷에 사용할 수 없음")
    void create_nonAssetTypeCategory_rejected() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        FinanceCategory expenseCategory = new FinanceCategory(categoryId, null, null, userId,
                FinanceCategory.Type.EXPENSE, "식비", 0, null);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(expenseCategory);

        assertThatThrownBy(() -> assetSnapshotService.create(userId, null, command()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(assetSnapshotPort, never()).save(any());
    }

    @Test
    @DisplayName("접근 불가한 카테고리를 지정하면 SecurityException")
    void create_inaccessibleCategory_rejected() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        FinanceCategory othersPersonalCategory = new FinanceCategory(categoryId, null, null, UUID.randomUUID(),
                FinanceCategory.Type.ASSET, "투자", 0, null);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(othersPersonalCategory);

        assertThatThrownBy(() -> assetSnapshotService.create(userId, null, command()))
                .isInstanceOf(SecurityException.class);
        verify(assetSnapshotPort, never()).save(any());
    }

    @Test
    @DisplayName("delete는 load-then-verify 후 softDelete 호출")
    void delete_callsSoftDelete() {
        when(assetSnapshotPort.findByIdOrThrow(snapshotId)).thenReturn(personalSnapshot());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());

        assetSnapshotService.delete(snapshotId, userId);

        verify(assetSnapshotPort).softDelete(snapshotId);
    }

    @Test
    @DisplayName("delete 시 접근 불가한 스냅샷이면 SecurityException")
    void delete_notAccessible_throwsSecurityException() {
        AssetSnapshot othersSnapshot = new AssetSnapshot(snapshotId, null, categoryId, accountId, UUID.randomUUID(),
                LocalDate.of(2026, 1, 1), AssetClass.CASH, Market.DOMESTIC, null, 1_000_000L, null);
        when(assetSnapshotPort.findByIdOrThrow(snapshotId)).thenReturn(othersSnapshot);
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assetSnapshotService.delete(snapshotId, userId))
                .isInstanceOf(SecurityException.class);

        verify(assetSnapshotPort, never()).softDelete(any());
    }
}
