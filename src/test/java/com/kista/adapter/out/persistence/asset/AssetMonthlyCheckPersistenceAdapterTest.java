package com.kista.adapter.out.persistence.asset;

import com.kista.domain.model.asset.AssetMonthlyCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssetMonthlyCheckPersistenceAdapter 단위 테스트")
class AssetMonthlyCheckPersistenceAdapterTest {

    @Mock AssetMonthlyCheckJpaRepository jpaRepository;
    @InjectMocks AssetMonthlyCheckPersistenceAdapter adapter;

    private final UUID userId = UUID.randomUUID();

    private AssetMonthlyCheckEntity entity(String month, boolean completed) {
        AssetMonthlyCheckEntity e = new AssetMonthlyCheckEntity();
        e.setUserId(userId);
        e.setMonth(month);
        e.setCompleted(completed);
        return e;
    }

    @Test
    @DisplayName("findByUserId: userId로 완료 상태 목록 반환")
    void findByUserId_returns_list() {
        when(jpaRepository.findByUserId(userId)).thenReturn(List.of(entity("2026-08", true)));

        List<AssetMonthlyCheck> result = adapter.findByUserId(userId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().month()).isEqualTo("2026-08");
        assertThat(result.getFirst().completed()).isTrue();
    }

    @Test
    @DisplayName("upsert: 네이티브 upsert 쿼리 위임 후 요청값 그대로 반환")
    void upsert_delegates_to_native_query() {
        AssetMonthlyCheck result = adapter.upsert(userId, "2026-08", true);

        verify(jpaRepository).upsert(userId, "2026-08", true);
        assertThat(result).isEqualTo(new AssetMonthlyCheck("2026-08", true));
    }

    @Test
    @DisplayName("deleteByUserId: userId 기준 일괄 삭제 위임")
    void deleteByUserId_delegates() {
        adapter.deleteByUserId(userId);

        verify(jpaRepository).deleteByUserId(userId);
    }
}
