package com.kista.application.service.asset;

import com.kista.domain.model.asset.Asset;
import com.kista.domain.model.asset.AssetCategory;
import com.kista.domain.model.asset.RegisterAssetCommand;
import com.kista.domain.model.asset.UpdateAssetCommand;
import com.kista.domain.port.out.AssetPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssetService 단위 테스트")
class AssetServiceTest {

    @Mock AssetPort assetPort;
    @InjectMocks AssetService assetService;

    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();

    private Asset existingAsset(UUID ownerId) {
        return new Asset(assetId, ownerId, LocalDate.of(2026, 8, 1), AssetCategory.INVESTMENT,
                "연금저축펀드", "미래에셋증권", "미국주식", "VR", 1_000_000L, null);
    }

    private RegisterAssetCommand registerCmd() {
        return new RegisterAssetCommand(LocalDate.of(2026, 8, 1), AssetCategory.INVESTMENT,
                "연금저축펀드", "미래에셋증권", "미국주식", "VR", 1_000_000L);
    }

    @Test
    @DisplayName("자산 등록 성공")
    void register_success() {
        when(assetPort.save(any())).thenAnswer(inv -> {
            Asset a = inv.getArgument(0);
            return new Asset(UUID.randomUUID(), a.userId(), a.entryDate(), a.category(),
                    a.subcategory(), a.institution(), a.assetClass(), a.strategy(), a.amount(), null);
        });

        Asset result = assetService.register(userId, registerCmd());

        assertThat(result.id()).isNotNull();
        assertThat(result.category()).isEqualTo(AssetCategory.INVESTMENT);
        verify(assetPort).save(any());
    }

    @Test
    @DisplayName("금액이 음수면 IllegalArgumentException 발생 (→ 400)")
    void register_negativeAmount_throws() {
        RegisterAssetCommand cmd = new RegisterAssetCommand(LocalDate.of(2026, 8, 1),
                AssetCategory.INVESTMENT, "연금저축펀드", null, "미국주식", null, -1L);

        assertThatThrownBy(() -> assetService.register(userId, cmd))
                .isInstanceOf(IllegalArgumentException.class);

        verify(assetPort, never()).save(any());
    }

    @Test
    @DisplayName("타 사용자 자산 기록 수정 시 SecurityException 발생 (→ 403)")
    void update_by_non_owner_throws_forbidden() {
        when(assetPort.requireOwnedAsset(assetId, otherUserId))
                .thenThrow(new SecurityException("소유자가 아닙니다"));

        UpdateAssetCommand cmd = new UpdateAssetCommand(LocalDate.of(2026, 8, 1),
                AssetCategory.INVESTMENT, "연금저축펀드", null, "미국주식", null, 1_000_000L);

        assertThatThrownBy(() -> assetService.update(assetId, otherUserId, cmd))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("본인 자산 기록 수정 성공")
    void update_by_owner_success() {
        when(assetPort.requireOwnedAsset(assetId, userId)).thenReturn(existingAsset(userId));
        when(assetPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateAssetCommand cmd = new UpdateAssetCommand(LocalDate.of(2026, 9, 1),
                AssetCategory.LOAN, "전세자금대출", "국민은행", "원화", null, 50_000_000L);
        Asset result = assetService.update(assetId, userId, cmd);

        assertThat(result.category()).isEqualTo(AssetCategory.LOAN);
        assertThat(result.amount()).isEqualTo(50_000_000L);
    }

    @Test
    @DisplayName("존재하지 않는 자산 기록 수정 시 NoSuchElementException 발생 (→ 404)")
    void update_not_found_throws() {
        when(assetPort.requireOwnedAsset(assetId, userId))
                .thenThrow(new NoSuchElementException("자산 기록을 찾을 수 없습니다: " + assetId));

        UpdateAssetCommand cmd = new UpdateAssetCommand(LocalDate.of(2026, 8, 1),
                AssetCategory.INVESTMENT, "연금저축펀드", null, "미국주식", null, 1_000_000L);

        assertThatThrownBy(() -> assetService.update(assetId, userId, cmd))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("타 사용자 자산 기록 삭제 시 SecurityException 발생 (→ 403)")
    void delete_by_non_owner_throws_forbidden() {
        when(assetPort.requireOwnedAsset(assetId, otherUserId))
                .thenThrow(new SecurityException("소유자가 아닙니다"));

        assertThatThrownBy(() -> assetService.delete(assetId, otherUserId))
                .isInstanceOf(SecurityException.class);

        verify(assetPort, never()).delete(any(UUID.class));
    }

    @Test
    @DisplayName("본인 자산 기록 삭제 성공")
    void delete_by_owner_success() {
        when(assetPort.requireOwnedAsset(assetId, userId)).thenReturn(existingAsset(userId));

        assetService.delete(assetId, userId);

        verify(assetPort).delete(assetId);
    }

    @Test
    @DisplayName("listByUser: userId로 자산 기록 목록 위임")
    void listByUser_delegates() {
        when(assetPort.findByUserId(userId)).thenReturn(java.util.List.of(existingAsset(userId)));

        assertThat(assetService.listByUser(userId)).hasSize(1);
        verify(assetPort).findByUserId(userId);
    }
}
