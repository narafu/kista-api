package com.kista.adapter.out.persistence.asset;

import com.kista.domain.model.asset.Asset;
import com.kista.domain.model.asset.AssetCategory;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssetPersistenceAdapter 단위 테스트")
class AssetPersistenceAdapterTest {

    @Mock AssetJpaRepository assetJpaRepository;
    @InjectMocks AssetPersistenceAdapter adapter;

    private final UUID assetId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private AssetEntity assetEntityWithId(UUID id) {
        AssetEntity e = new AssetEntity();
        e.setId(id);
        e.setUserId(userId);
        e.setEntryDate(LocalDate.of(2026, 8, 1));
        e.setCategory(AssetCategory.INVESTMENT);
        e.setSubcategory("연금저축펀드");
        e.setInstitution("미래에셋증권");
        e.setAssetClass("미국주식");
        e.setStrategy("VR");
        e.setAmount(1_000_000L);
        return e;
    }

    @Test
    @DisplayName("save: 신규 자산 기록 저장 시 JPA save 호출")
    void save_new_asset_delegates_to_jpa() {
        Asset newAsset = new Asset(null, userId, LocalDate.of(2026, 8, 1), AssetCategory.INVESTMENT,
                "연금저축펀드", "미래에셋증권", "미국주식", "VR", 1_000_000L, null);

        when(assetJpaRepository.save(any())).thenReturn(assetEntityWithId(assetId));

        Asset result = adapter.save(newAsset);

        verify(assetJpaRepository).save(any(AssetEntity.class));
        assertThat(result.id()).isEqualTo(assetId);
        assertThat(result.amount()).isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("findById: 존재하는 자산 기록 반환")
    void findById_returns_asset_when_exists() {
        when(assetJpaRepository.findById(assetId)).thenReturn(Optional.of(assetEntityWithId(assetId)));

        Optional<Asset> result = adapter.findById(assetId);

        assertThat(result).isPresent();
        assertThat(result.get().category()).isEqualTo(AssetCategory.INVESTMENT);
    }

    @Test
    @DisplayName("findById: 없는 자산 기록 empty 반환")
    void findById_returns_empty_when_not_found() {
        when(assetJpaRepository.findById(assetId)).thenReturn(Optional.empty());

        assertThat(adapter.findById(assetId)).isEmpty();
    }

    @Test
    @DisplayName("findByUserId: userId로 자산 기록 목록 반환")
    void findByUserId_returns_list() {
        when(assetJpaRepository.findByUserIdOrderByEntryDateDescIdDesc(userId))
                .thenReturn(List.of(assetEntityWithId(assetId)));

        List<Asset> result = adapter.findByUserId(userId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().userId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("delete: assetId 소프트 삭제 호출")
    void delete_delegates_to_jpa() {
        adapter.delete(assetId);

        verify(assetJpaRepository).softDeleteById(eq(assetId), any());
    }

    @Test
    @DisplayName("deleteByUserId: userId 기준 일괄 소프트 삭제 호출")
    void deleteByUserId_delegates_to_jpa() {
        adapter.deleteByUserId(userId);

        verify(assetJpaRepository).softDeleteByUserId(eq(userId), any());
    }
}
